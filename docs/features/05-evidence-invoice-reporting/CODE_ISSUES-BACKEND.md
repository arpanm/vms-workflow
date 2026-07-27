# F05 Backend Code Issues

**Source:** Independent Terra backend code review (`CODE_REVIEW-BACKEND.md`)  
**Gate:** **NO-GO** until every P0 is fixed and covered by automated evidence.

| ID | Priority | Status | Required remediation | Required automated proof |
|---|---|---|---|---|
| F05-BE-001 | P0 | Open | Build a typed F04 evidence resolver and construct the required complete package item set from exact, scan-passed, current source versions; evaluate each of the nine pillars independently with truthful outcome/severity/source/CTA. | Deterministic package and nine-pillar pass/fail tests; missing/stale/superseded source tests. |
| F05-BE-002 | P0 | Open | Add provider-neutral private storage and malware scan adapters. Persist objects only in private scoped storage; use `PENDING`/`UNKNOWN` until a scan result; quarantine/deny failed or unknown objects. Never make local metadata inspection a security pass. | Upload/scan unavailable, malware, MIME mismatch, quarantine and unauthorized download tests. |
| F05-BE-003 | P0 | Open | Require approved-for-processing (and exact approved evidence lineage) before AP status updates; use an explicit AP/finance scope and legal payment/invoice transition matrix. | Draft/submitted/rejected payment update denial; valid AP timeline and concurrency tests. |
| F05-BE-004 | P0 | Open | Make exception acceptance append a rule-bound exception that changes only the relevant readiness disposition to disclosed exception for the exact version, preserves failed source facts, and defines the permitted subsequent state transition. | Blocked-rule-only, expiry, second-approver, submit/process after allowed exception and no-confirmation-rewrite tests. |
| F05-BE-005 | P0 | Open | Implement durable package/readiness/export worker claim/retry/dead-letter/replay paths; render/scan/output-hash exports and only enable download after authorization and scan pass. | Worker success/crash/retry/dead-letter/idempotency, private download and CSV formula-escape tests. |
| F05-BE-006 | P0 | Open | Enforce append-only/guarded transitions for artifacts, package headers, invoices and readiness runs; allow only documented lifecycle updates with audit/event provenance. | Direct SQL immutability/transition rejection tests after submit, approval and invalidation. |
| F05-BE-007 | P1 | Open | Record and validate an explicit compatible F04 handoff contract (required fields, schema version, scope, freshness, hash); reject unknown/incomplete/incompatible payloads. | Compatible/unknown-future/duplicate/reordered F04 contract tests. |
| F05-BE-008 | P1 | Open | Route invalidation through the transactional F05 journal or create an outbox event in the trigger transaction; add auditable, idempotent downstream invalidation/replay. | F04 reopen/invalidation E2E with exactly one F05 event/outbox effect. |
| F05-BE-009 | P1 | Open | Add package share records and scoped create/list/revoke endpoints with recipient, expiry, revocation, audit and non-broadening access semantics. | Expired/revoked/cross-tenant share and access-audit tests. |
| F05-BE-010 | P1 | Open | Add composite foreign keys/triggers validating artifact organization/month against invoice/package scope and enforce it for outputs/items. | Cross-month/cross-organization attachment rejection tests. |
| F05-BE-011 | P1 | Open | Resolve an effective `f05_policy_versions` row for every action and persist its ID/version in manifest/readiness/action records; make rule severity and retention/MIME behavior policy-driven. | Policy effective-date, changed policy hash and non-blocking disclosure tests. |
| F05-BE-012 | P1 | Open | Store `invoice_date` as represented immutable metadata/column and return it exactly; include it in version hash and correction lineage. | Create/read/version/correction invoice-date fidelity tests. |
| F05-BE-013 | P1 | Open | Derive dashboard/report permissions per engagement and actor using the same authorization boundary as mutations; do not emit capabilities the caller lacks. | Read-only/vendor/procurement/AP capability contract tests. |
| F05-BE-014 | P1 | Open | Deliver the specified persona dashboards, matrix cells/non-color states, queues, drill-down lineage and full scoped report/metric dictionary catalog. | Persona/cross-tenant dashboard and report-contract E2E tests. |
| F05-BE-015 | P2 | Open | Implement stable server-side cursor pagination with bounded limits and scope-aware ordering for every list endpoint. | Cursor continuity, no duplicate/skip and unauthorized filter tests. |
| F05-BE-016 | P2 | Open | Specify and enforce canonical array sort keys, UTC serialization and null/omission rules; test byte-for-byte stability over reordered equivalent source collections. | Canonical fixture/property tests and hash corruption detection. |
| F05-BE-017 | P2 | Open | Add authorized query response/close/cancel endpoints and preserve correction/reopen lineage. | Owner/respondent authorization and query lifecycle tests. |
| F05-BE-018 | P2 | Open | Model/derive a distinct finance-AP party (or explicitly document controlled Procurement/AP colocation) and use it for payment authorization. | AP allowed; Procurement-only denied; SOD tests. |
| F05-BE-019 | P2 | Open | Include file SHA-256 in document idempotency intent and return typed idempotency mismatch on different content. | Same key + different byte content conflict test. |
| F05-BE-020 | P3 | Open | Remove or adopt the duplicate DTO model and delete dead controller mapping code. | Compile/OpenAPI snapshot test. |
| F05-BE-021 | P3 | Open | Set response media type and filename from authorized stored output metadata. | JSON/PDF/CSV/XLSX content-disposition tests. |

## Issue counts

- P0: 6
- P1: 8
- P2: 5
- P3: 2
- Total: 21

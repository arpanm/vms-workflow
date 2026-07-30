# F06 — Review Finding Fix Disposition

## 30 July 2026 completion-audit remediation

V41 and API/UI changes close retro outcomes, Procurement delivery envelopes,
historical lifecycle readiness, bounded row/conflict workbench, upload
provenance persistence, missing stable validation codes and request
correlation. V43 and the completion batch also add durable async execution,
the exact 100k boundary, tenant-generated CSV/XLSX templates, executable
OpenAPI parity, F06-specific accessibility and consumed-package correction
routing. Consolidated regression remains pending; these are not externalized.

This document links the independent review findings to the implemented
remediation. Exact passing commands and counts are recorded in
`docs/FEATURE_STATUS.md`; external provider and rehearsal gates remain
`ACTION_REQUIRED`.

| Finding | Disposition | Implemented evidence |
|---|---|---|
| API/DTO drift and mock-only acceptance | Fixed locally | Controller/client contracts are aligned; the six-case non-intercepted system lane traverses Vite, Spring Security/API, Flyway and PostgreSQL. |
| Generic canonical facts | Fixed | Every one of the 14 physical templates invokes `MigrationDomainAdapter`; `MigrationDomainAdapterIT` asserts authoritative domain records. |
| Duplicate after domain mutation | Fixed | Commit locks/checks the canonical natural key before invoking an adapter; the racing duplicate integration case asserts zero new employee/domain effect. |
| Rollback left domain effects active | Fixed | Flyway V19 records ordered effects and append-only compensation; rollback reverses unconsumed insert/update effects and retains provenance/audit. |
| Client-selected/ambiguous SoD authority | Fixed | The server derives exactly one active scoped authority. Forged, ambiguous, disabled, expired, stale-hash and same-authority-organization cases are denied. |
| Reprocess repeated successful rows | Fixed | `REPROCESS_REJECTS` children stage only rejected parent row numbers and preserve lineage. |
| Attendance authority/timezone | Fixed locally | Raw timestamps are converted using the validated IANA zone and raw/daily authority is non-additive. |
| Reconciliation content | Fixed locally | Pre-commit coverage derives real employee, leave, attendance, delivery, Linear, certification, confirmation, invoice/hash and confidence values. |
| Unsigned/unimplemented cursor | Fixed | Cursor is opaque, canonical Base64URL, signed, actor/resource/scope/snapshot bound and tamper rejected. |
| Formula safety changed stored values | Fixed | Validated business values remain canonical; only CSV/export sinks neutralize formula prefixes. |
| Historical actor/evidence semantics | Fixed locally | Deliverable owners/assignments/criteria/dependencies, certification/confirmation authorities, invoice evidence hash linkage and approval-history authority are mapped to governed records. |
| Approval request omitted decision | Fixed | Both job approvals and reconciliation sign-offs validate an explicit decision; the Migration Center sends `APPROVED` and malformed requests fail as typed 400 responses. |
| Partial-commit control was cosmetic | Fixed | The policy is recorded at upload, cannot be changed later, and commit explicitly reaffirms the persisted policy. |
| Delivery-child compensation missing | Fixed | Assignment/dependency effects are compensated in reverse sequence with exact job/action/provenance binding. |
| Scoped object enumeration | Fixed | Existing unauthorized and absent job/report identifiers produce the same non-disclosing not-found response. |
| Provenance rejected multi-effect rows | Fixed | V19 keys effects by job/row/effect sequence, allowing legitimate insert-plus-update effects without weakening exact rollback provenance. |
| Production scanner/storage | External gate | The local adapter fails closed and supports deterministic testing. Approved production scanner/object-storage credentials, callback controls and rehearsal evidence are `F06-EXT-002`. |

No finding is closed merely because a Markdown file exists. The status ledger
and final review carry the evidence-backed state after each rerun.

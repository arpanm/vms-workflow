# F05 Post-fix Backend Review

**Reviewer:** Terra independent post-fix review  
**Scope:** Current uncommitted F05 Java backend, Flyway migration and F05 backend tests.  
**Basis:** Requirements 10, 12, 13, 14 and 16; F05 `TASKS.md` and `TEST_CASES.md`.  
**Method:** Static review only; no Maven/test command was run so as not to overlap the active validation gate.

## Decision

**NO-GO — 1 P0, 5 P1, 5 P2 and 2 P3 issues remain (13 total).**

The post-fix implementation is materially stronger. It now has a typed F04 contract resolver, private database-backed artifacts with checksum verification, an explicit scan adapter, F05 share/revoke authorization, invoice transition guards, a finance/AP party, export work leasing and rendering, scoped capability derivation, and controller-level cursor paging. However, the required exception workflow is still unreachable for any blocked invoice, so an authorized Procurement exception cannot be used as specified. This blocks F05 release.

## Prior review disposition

| Prior ID | Disposition | Evidence |
|---|---|---|
| F05-BE-001 | **Partially fixed** | Typed resolver and nine explicit readiness rules/package manifest items are present, but source-artifact completeness remains P1-02. |
| F05-BE-002 | **Fixed locally; external gate remains** | Bytes are privately persisted, SHA-256 checked and scan state is stored before download/readiness. Local scanner must not be treated as production malware assurance. |
| F05-BE-003 | **Fixed** | Payment update now requires FINANCE party, approved invoice state and exact approved lineage. |
| F05-BE-004 | **Not fixed** | New exception-derived readiness exists, but the state rules make acceptance unreachable for blocked evidence (P0-01). |
| F05-BE-005 | **Fixed locally** | Leased export worker, retries, dead-letter state, private output storage, scan and download verification are implemented. |
| F05-BE-006 | **Mostly fixed** | Transition/immutability guards now cover artifacts, blobs, package headers, invoices and readiness runs; narrow audit gaps remain P2-03. |
| F05-BE-007 | **Fixed** | `FinanceF04EvidenceResolver` validates schema/hash/readiness/facts and records consumption. |
| F05-BE-008 | **Fixed** | F04 invalidation trigger now creates a transactional F05 outbox row. |
| F05-BE-009 | **Fixed** | Expiring/revocable shares and package-grant authorization are implemented. |
| F05-BE-010 | **Fixed** | Artifact-month scope triggers cover invoice version, package item and package output insertion. |
| F05-BE-011 | **Partially fixed** | Package generation resolves/persists an effective policy; upload/readiness/report behavior remains hard-coded (P1-03). |
| F05-BE-012 | **Fixed** | `invoice_date` is persisted and read as represented metadata. |
| F05-BE-013 | **Fixed** | Governance capability list is actor/engagement-derived. |
| F05-BE-014 | **Partially fixed** | Matrix cells and expanded report definitions exist; masking/persona/queue completeness remain P1-04. |
| F05-BE-015 | **Partially fixed** | Generic controller paging is implemented, but several accepted cursors are unused (P2-01). |
| F05-BE-016 | **Fixed with a documented canonical array key policy** | Canonical object/array normalization is now deterministic for supported manifest shapes. |
| F05-BE-017 | **Fixed** | Owner response and Procurement close/cancel flows are present. |
| F05-BE-018 | **Partially fixed** | FINANCE party exists, but discovery/export actor scope omits finance organizations (P1-05). |
| F05-BE-019 | **Fixed** | Upload idempotency intent includes content SHA-256. |
| F05-BE-020 | **Fixed** | Duplicate `FinanceDtos` and controller dead mapping are removed. |
| F05-BE-021 | **Fixed** | Invoice, package and export downloads now derive media type/filename from authorized artifact metadata. |

## What is now ready

- All normal finance mutations are transactional and journaled with idempotency, domain event and audit rows.
- F04 incompatibility, checksum mismatch and missing required pillar facts are rejected before a package is produced.
- Invoice and package downloads require server-side authorization, scan pass and checksum verification; no provider credentials or object keys are returned.
- Export jobs use `FOR UPDATE SKIP LOCKED`, leases, bounded retry and dead-letter state; CSV/XLSX renderer code escapes formula-leading values.
- Database guards prevent generic history deletes and invalid lifecycle transitions. The migration remains additive and preserves F02–F04 source tables.

See [POST_FIX_ISSUES-BACKEND.md](POST_FIX_ISSUES-BACKEND.md) for required fixes and tests.

## Final evidence addendum — 2026-07-30

Export leases now fence completion and allow safe expired-claim recovery.
Exception handling uses policy-declared rules and appends immutable exact-bound
readiness lineage. Exact Finance recovery passed **1/1** and local-system
finance passed **4/4**. No clean aggregate is claimed; performance/DR,
F07-T057 and external G4 remain separate gates.

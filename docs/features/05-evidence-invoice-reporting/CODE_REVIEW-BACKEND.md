# F05 Backend Code Review

**Reviewer:** Terra independent review  
**Scope:** Uncommitted F05 Java backend only: `V14__finance_evidence_invoice_reporting.sql`, `FinanceController`, `Finance*` application services, and `FinanceAuthorizationService`.  
**Review basis:** F05 `TASKS.md` / `TEST_CASES.md`, requirements 10, 12, 13 and 14.  
**Method:** Static contract, state-machine, persistence-integrity and authorization review. `mvn -q -f backend/pom.xml -DskipTests compile` passed. No production code was modified by this review.

## Decision

**NO-GO — 6 P0, 8 P1, 5 P2 and 2 P3 issues (21 total).**

The implementation establishes a useful server-side starting point, but it does not yet deliver the required evidence or payment controls. In particular, it can label an unscanned/non-persisted invoice document as `PASSED`, generate a package containing only an F04 handoff, advance payment history before Procurement approval, and leaves requested exports permanently pending. Those are release blockers for RQ-024, RQ-025, RQ-026 and RQ-032.

## What is implemented well

- The feature is additive and does not reintroduce Lovable, Supabase or client-side database access. The Flyway migration creates a dedicated F05 namespace of finance evidence, audit, outbox and idempotency tables.
- Request paths derive actor identity from the JWT and use scoped authorization service calls rather than accepting a client-supplied organization or role. Invoice creation/upload/submit use vendor-party checks; review/query/exception use Procurement-party checks.
- Invoice create, document versioning, readiness, submission, review, query, exception and payment mutation paths use transactions, optimistic version checks and idempotency keys (`FinanceInvoiceService:257-656`, `FinanceGovernanceService:96-390`).
- The schema has useful partial uniqueness constraints for one current package, one primary active invoice and current readiness result (`V14:144-146`, `226-233`, `282-284`), along with append-only triggers for several child histories (`529-577`).
- Canonical JSON at least sorts object keys and hashes UTF-8 bytes, and the package manifest separates its canonical input hash from an output hash (`FinanceCanonicalJson:27-63`, `FinancePackageService:96-162`).
- The API documentation surface is authenticated by the existing security configuration; the new controller has operation summaries.

## Release-blocking findings

| ID | Priority | Finding | Evidence |
|---|---|---|---|
| F05-BE-001 | P0 | Package generation and readiness do not prove the required nine pillars or package contents. A package copies only the F04 handoff plus a JSON output; it never resolves invoice document, roster/allocation, attendance, plan/Linear, certification/confirmation artifacts or checks their scan/hash/current status. Every upstream readiness rule is reduced to one boolean `handoffReady`, so a handoff that lacks mandatory facts still produces eight `PASS` results. | `FinancePackageService:96-162`; `FinanceInvoiceService:518-560`, `1064-1109` |
| F05-BE-002 | P0 | Upload marks arbitrary structurally-sniffed content `PASSED` with `LOCAL_METADATA_ONLY`, yet does not persist the bytes to private storage or invoke a scanner. It therefore makes unscanned content eligible for readiness and release, contrary to the scan/quarantine gate. | `FinanceInvoiceService:373-396`, `518-524`; requirement 14 §7 |
| F05-BE-003 | P0 | Payment transitions have no prerequisite that the invoice is approved for processing (or even submitted). A scoped AP actor can append `SUBMITTED_TO_AP → … → PAID` to a `DRAFT` invoice, and the method mutates invoice state accordingly. | `FinanceGovernanceService:341-390`, `841-857` |
| F05-BE-004 | P0 | Exception acceptance neither produces a current readiness result of `EXCEPTION_ACCEPTED_BY_PROCUREMENT` nor an eligible, submit/processable workflow. It only sets invoice status to `EXCEPTION_ACCEPTED`; submission requires `READY_FOR_VENDOR_SUBMISSION` and an eligible run, so the stated exception route is unusable and its disclosure is not reflected in readiness. | `FinanceGovernanceService:253-314`; `FinanceInvoiceService:611-633`, `832-888` |
| F05-BE-005 | P0 | Report exports can be requested but no F05 worker ever claims/renders/scans/completes them; download unconditionally fails. This means the async report/export feature is permanently `PENDING`, including the required CSV/XLSX/PDF/JSON delivery path. | `FinanceGovernanceService:461-558`; no F05 worker found; `V14:401-424` |
| F05-BE-006 | P0 | Private artifact and parent package/invoice records remain mutable/deletable at the database boundary. The immutable trigger list omits `f05_private_artifacts`, `evidence_package_versions`, `invoices` and `invoice_readiness_runs`; an accidental or privileged generic update can alter source hashes, scan state or closed evidence without an append-only fact. | `V14:83-113`, `115-143`, `181-225`, `529-577` |

## High-priority findings

| ID | Priority | Finding | Evidence |
|---|---|---|---|
| F05-BE-007 | P1 | The declared F04 contract adapter is not implemented. `f05_handoff_consumptions` is never written, contract/schema compatibility is not inspected, and the service accepts any effective handoff from a view without rejecting incomplete, stale or future-incompatible manifest content. | `V14:72-81`; `FinancePackageService:67-82`; `FinanceInvoiceService:1011-1036` |
| F05-BE-008 | P1 | The F04 invalidation trigger inserts a domain event directly but never creates an outbox row. It also changes package/readiness/invoice state without an F05 service-level audit event. Downstream consumers therefore cannot reliably observe/replay invalidation. | `V14:642-702`; contrast `FinanceMutationJournal:86-109` |
| F05-BE-009 | P1 | Package sharing/revocation is absent: no share table, no endpoints, no authorization/expiry/revocation checks, and the access view returns empty `expiresAt`/`revokedAt` placeholders. | `V14:115-180`; `FinanceController:205-251`; `FinancePackageService:249-271` |
| F05-BE-010 | P1 | Evidence child links are not scope-safe in the database. `evidence_package_items.artifact_id`, `evidence_package_outputs.artifact_id`, and `invoice_versions.document_artifact_id` use single-column FKs with no trigger/composite key proving the artifact belongs to the same engagement month/organization. A direct/integration write can attach cross-month restricted content. | `V14:148-179`, `235-250`; compare package/invoice-only scope gates at `579-640` |
| F05-BE-011 | P1 | `f05_policy_versions` is never selected or enforced. Readiness and package responses hard-code `f05-policy-v1`, so rule blocking/non-blocking policy, MIME/retention, exception authority and policy effective dates are not versioned at runtime. | `V14:57-70`; `FinanceInvoiceService:488`, `538-543`; `FinancePackageService:342-343` |
| F05-BE-012 | P1 | Invoice data fidelity is wrong: invoice date is not a column in `invoices`, and the read DTO substitutes the billing-period start for `invoiceDate`. An uploaded represented invoice cannot be reproduced accurately from the primary model. | `V14:181-225`; `FinanceInvoiceService:727-738` |
| F05-BE-013 | P1 | The server sends capabilities that the actor may not have. `governanceReadPermissions()` always returns review, exception, payment-update and export permissions to every dashboard/control-tower caller; callers with read-only access can be shown privileged actions and data flow contracts become untrustworthy. | `FinanceGovernanceService:53-94`, `641-645` |
| F05-BE-014 | P1 | The reporting/control-tower implementation is only a thin invoice/package list with two report definitions. It lacks the defined readiness matrix cells, required queues (confirmation/attendance/change/hold/exception/historical/payment aging), persona distinctions, metric numerator/denominator semantics, and the required report catalog. | `FinanceGovernanceService:393-458`, `605-645`, `972-996`; requirement 12 §§3–12 |

## Medium- and low-priority findings

| ID | Priority | Finding | Evidence |
|---|---|---|---|
| F05-BE-015 | P2 | F05 list endpoints accept a `cursor` but ignore it and always return all records with `nextCursor: null`. This violates the required cursor pagination and is a scale/performance risk. | `FinanceController:60-86`, `177-185`, `224-232`, `253-260`, `333-340`, `379-384` |
| F05-BE-016 | P2 | Canonicalization sorts object keys but not arrays by declared business keys, has no explicit null/omission policy, and relies on mapper temporal formatting. Hash stability across equivalent upstream array ordering and deployments is not guaranteed. | `FinanceCanonicalJson:16-18`, `27-63`; `FinancePackageService:96-106` |
| F05-BE-017 | P2 | Procurement queries are write-only. The model has response rows, but no endpoint/service to respond, close or cancel a query, no assignee authorization for it, and no correction/reopen linkage beyond a fixed UI path. | `V14:352-374`; `FinanceController:276-288`; `FinanceGovernanceService:161-226` |
| F05-BE-018 | P2 | Payment authority is bound to the Procurement party even though `payment.update` is assigned to the `FINANCE_AP` role. The domain model has no finance-party organization, so this either blocks legitimate AP actors or conflates Procurement and AP duties. | `V14:16-19`, `43-47`; `FinanceGovernanceService:332-334`; `FinanceAuthorizationService:15-18`, `111-131` |
| F05-BE-019 | P2 | The upload idempotency request hash excludes document bytes/content hash; two same-size files with the same name and metadata are treated as the same intent. A retry with altered content can return the prior artifact instead of conflicting. | `FinanceInvoiceService:358-363`, `1388-1400` |
| F05-BE-020 | P3 | `FinanceDtos.java` duplicates a second DTO model but is not used by the controller, creating a maintainability and OpenAPI-drift hazard. `representedMetadata` in the controller is likewise dead code. | `backend/src/main/java/com/vms/workflow/api/FinanceDtos.java`; `FinanceController:407-422` |
| F05-BE-021 | P3 | Download content metadata is hard-coded to JSON/package filename and export `.bin`, regardless of the stored output/export format. When real outputs are added this will mislabel clients and audit evidence. | `FinanceController:241-250`, `367-376`; `FinancePackageService:420-444` |

## Required fix order

1. Close F05-BE-001 through F05-BE-006, then add integration tests for each blocking path before declaring any F05 workflow usable.
2. Implement typed F04 contract consumption/invalidation outbox, storage/scan/render/export workers and the complete share/revoke model (F05-BE-007 through F05-BE-011).
3. Correct invoice representation, role-derived capability payloads, reporting contracts, query lifecycle and pagination.
4. Re-run the F05 API, migration, cross-tenant, idempotency/concurrency and Playwright E2E cases in `TEST_CASES.md`; this review must be repeated after fixes.

## Superseding evidence addendum — 2026-07-30

The historical findings above remain for traceability. Current implementation
uses a versioned business-rule exception allowlist, non-waivable document/
manifest integrity rules, append-only exception readiness lineage and
live-lease export recovery. Exact Finance recovery passed **1/1** and the local
finance system lane passed **4/4**. The integrated Maven aggregate was not
clean (340 executed, 2 failures, 1 error); performance/DR, F07-T057 and G4 are
not closed by the focused recovery.

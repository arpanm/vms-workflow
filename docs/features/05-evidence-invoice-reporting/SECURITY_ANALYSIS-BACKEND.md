# F05 Backend Security Analysis

**Result:** **NO-GO** because P0-01 impacts governed exception authorization; remaining deployment/security controls are listed below.

## Positive controls

- JWT subject is the only actor input; no finance organization or role is accepted from the client.
- Package shares are explicit, expiry/revocation-aware and require active identity lookup (`FinanceAuthorizationService:104-141`).
- Downloads are authorization-, scan- and hash-gated (`FinanceInvoiceService:274-317`, `FinancePackageService:303-343`, `FinanceGovernanceService:673-714`).
- File MIME is allowlisted and sniffed, upload size is bounded, names are sanitized, and idempotency covers content hash.
- PDF/CSV/XLSX outputs avoid direct HTML handling; CSV/XLSX formula-prefix escaping is implemented in `LocalFinanceReportRenderer`.
- F05 history tables and parent lifecycle records have transition/immutability guards in `V14:624-936`.

## Security findings

| ID | Severity | Finding | Evidence |
|---|---|---|---|
| P0-01 | P0 | Procurement exception authorization cannot be exercised for the blocked evidence it is supposed to govern. This forces users toward an unsupported/manual state workaround and breaks the required authority-bound remediation control. | `FinanceInvoiceService:643-653`; `FinanceGovernanceService:344-352`, `971-980` |
| P1-04 | P1 | Reports/export output lacks a report-specific field-mask layer. The worker serializes full control-tower rows for every definition, and there is no masked-field contract tied to the requesting persona/organization. Screen/export masking parity is therefore unproven. | `FinanceOperationsWorker:157-189`; `FinanceGovernanceService:1356-1400` |
| P1-05 | P1 | Finance AP organization scope is added to authorization, but finance organizations are absent from engagement discovery and export actor-organization lookup. A separate AP organization may lose access to lists/exports or cause an ad-hoc role workaround that weakens separation of duties. | `FinanceAuthorizationService:193-229`; `FinanceInvoiceService:1328-1370`; `FinanceGovernanceService:716-759`, `1423-1444` |
| P2-03 | P2 | Artifact guard does not compare/protect `legal_hold`, `scan_engine`, `scan_reason_code` or `scanned_at`. Any database writer can change those forensic/security fields while preserving the permitted scan status. | `V14:706-745` |
| P2-04 | P2 | The default scanner labels a deterministic EICAR/header check as `CONFIGURED` and returns `PASSED` for all other content. It is acceptable only as a local/test adapter; production configuration must fail closed unless an approved scan provider is configured. | `LocalFinanceMalwareScanner:8-48`; `PostgresFinancePrivateStorageAdapter:25-36` |
| P2-05 | P2 | Finance export/download endpoints have no feature-specific rate or mass-export guard. Requirement 14 calls for rate limiting and security alerts around export/download abuse. | `FinanceController:424-460`; no finance rate-limit filter found |

## External acceptance gates

- Approved object storage with encryption/retention/legal hold, approved malware scanner, renderer hardening, AP/ERP callback authentication, operational database grants/RLS, and deployed rate limits remain external acceptance evidence. Do not mark these complete from the local adapters.

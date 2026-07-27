# F05 Backend Code Analysis

**Result:** **NO-GO** pending P0-01. Static analysis of the current F05 implementation.

## Architecture and correctness observations

- The F04 resolver (`FinanceF04EvidenceResolver:41-152`) is a clear typed boundary and correctly verifies handoff/schema/readiness hashes before F05 consumes evidence.
- The package flow (`FinancePackageService:72-206`) now stores policy, F04 manifest, F04 readiness manifest, source facts, a scan-passed invoice reference and an immutable rendered manifest output.
- The invoice upload path (`FinanceInvoiceService:421-524`) safely derives content hash, normalizes file name/MIME, persists the bytes privately, records scanner provenance and includes file content in the idempotency request hash.
- The exception flow is internally inconsistent: a blocked readiness makes the invoice `EVIDENCE_PENDING` (`FinanceInvoiceService:643-653`), while exception acceptance requires one of the reviewable states (`FinanceGovernanceService:344-346`, `971-980`). A blocked invoice cannot be submitted, so it can never reach that state.
- Export processing uses bounded batch claims, but failure rows are selected again automatically (`FinanceOperationsWorker:123-154`) despite their status being `FAILED`. This creates automatic retry without an explicit replay action, while the replay method itself has no API/authorization/audit surface.

## API and data-contract observations

- Controller pagination is an in-memory offset cursor. It prevents unbounded response bodies but does not provide stable snapshot pagination when source rows mutate; `control-tower` and `reports` accept cursors but discard them (`FinanceController:310-317`, `415-422`).
- `GeneratePackageInput.readinessRunId` is mandatory, but package generation does not validate or consume that UUID (`FinanceController:534-538`, `FinancePackageService:52-107`). The current integration test passes an F04 readiness run ID, confirming it is only hashed as client-controlled metadata.
- Package summary still reports hard-coded `f05-policy-v1` rather than its stored policy (`FinancePackageService:513-532`), causing DTO drift from the persisted manifest.
- Report renderer receives only `f05_control_tower` rows and no report-specific query/field mask (`FinanceOperationsWorker:157-189`). Expanded report definitions do not yet correspond to distinct datasets or metric formula implementations.

## Required non-security corrective work

1. Resolve P0-01 and add an end-to-end blocked-rule exception path.
2. Either remove `readinessRunId` from package generation or validate it as an exact in-scope F05 input and include server-derived value only.
3. Implement server-side keyset/snapshot pagination and apply it consistently.
4. Implement specific report data contracts and metric calculations before treating report definitions as delivered features.

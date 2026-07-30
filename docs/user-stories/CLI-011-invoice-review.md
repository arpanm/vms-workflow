# CLI-011 — View and approve invoices

**Persona:** Client approver
**Implementation:** IMPLEMENTED — F04 client confirmation and F05 invoice workspace
**Testing:** VERIFIED — finance system 4/4 and certification regression; [execution](TEST_EXECUTION.md)

## Description and UI flow

Use **Confirmation** to approve monthly delivery evidence and **Finance
workspace** to view the resulting invoice, package lineage and status.

## Acceptance criteria

- Client confirmation and Procurement invoice approval remain distinct stages.
- Invoice read is tenant/engagement scoped and exposes no rate/salary data.
- Exact package, delivery, approval and attendance lineage is visible.

## Test cases

- Client confirms the month and reads the submitted invoice.
- Cross-client invoice IDs are non-disclosing.

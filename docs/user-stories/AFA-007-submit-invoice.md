# AFA-007 — Submit invoice with governed monthly evidence

**Persona:** ArrowFoundry administrator/finance user
**Implementation:** IMPLEMENTED — F05 readiness/package/invoice submission
**Testing:** VERIFIED — finance system 4/4

## Description and UI flow

Generate the evidence package, review readiness, attach the invoice and submit.
The package includes approved assigned tasks, delivery outcomes/approvals and
attendance evidence for the represented month.

## Acceptance criteria

- Submission fails if any mandatory evidence/version is missing or stale.
- Package manifest and invoice bind to exact immutable versions.
- Successful submission enters Procurement’s pending queue once.

## Test cases

- Submit a fully ready invoice and inspect package lineage.
- Block missing attendance/delivery/client approval.

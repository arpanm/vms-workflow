# PRC-004 — Browse past invoices

**Persona:** Client procurement user
**Implementation:** IMPLEMENTED — F05 invoice history and package versions
**Testing:** VERIFIED — finance pagination/history suites

## Description and UI flow

Use Finance/Procurement history to browse earlier invoices, decisions,
queries, evidence package versions and payment status.

## Acceptance criteria

- Stable cursor pagination returns tenant-scoped chronological history.
- Past invoice decisions and package hashes remain immutable.
- Access control applies equally to old and current invoices.

## Test cases

- Page through multiple prior invoices without duplication.
- Reject cursor tampering and cross-tenant invoice ID.

# PRC-003 — View and download invoice evidence

**Persona:** Client procurement user
**Implementation:** IMPLEMENTED — F05 package/invoice private downloads
**Testing:** VERIFIED — finance access/integrity suites

## Description and UI flow

From Procurement, inspect/download the exact prior-month assigned/approved
tasks, delivery outcomes and approvals, attendance evidence and invoice.

## Acceptance criteria

- Download verifies stored hash and access scope before returning bytes.
- Package manifest binds every item to its immutable version.
- Access is logged and expired/revoked share grants fail.

## Test cases

- Download invoice and evidence package with expected digest.
- Reject tampered artifact and foreign/revoked access.

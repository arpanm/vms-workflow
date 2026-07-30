# AFA-011 — See attendance, leave balances and requests

**Persona:** ArrowFoundry administrator/HR
**Implementation:** IMPLEMENTED — F02 workforce administration
**Testing:** VERIFIED — workforce backend/browser/system suites

## Description and UI flow

Use Workforce Employees, Today, Leave and Month status to inspect employees,
attendance sessions, balances, requests and readiness exceptions.

## Acceptance criteria

- Workforce scope is organization-authorized.
- Balances derive from immutable ledger entries.
- Attendance sessions preserve overnight/split-shift policy.

## Test cases

- View all three datasets for an authorized vendor.
- Deny client/foreign organization administration.

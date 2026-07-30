# AFU-011 — Apply for leave and see balance/holidays

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — F02 Leave self-service
**Testing:** VERIFIED — workforce integration/browser suites

## Description and UI flow

Open **Leave** to view balances/calendar holidays and submit an effective-dated
leave request.

## Acceptance criteria

- Balance derives from immutable ledger and effective leave policy.
- Holiday/weekly-off dates are handled by policy.
- User can create/read only their linked employee requests.

## Test cases

- View balance/holidays and submit valid leave.
- Reject overlap, insufficient balance and foreign employee.

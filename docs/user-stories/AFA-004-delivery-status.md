# AFA-004 — Update and monitor delivery status

**Persona:** ArrowFoundry administrator
**Implementation:** IMPLEMENTED — V46 task status and F04 certification
**Testing:** AUTOMATED — status/version browser/integration tests

## Description and UI flow

Set task status and delivery summary in the work-item card; use Certification
for immutable month-end outcomes and client approval status.

## Acceptance criteria

- Status values are allow-listed and version-protected.
- Only assigned practitioners or scoped managers can update.
- Client L1/L2 decisions remain visible beside vendor status.

## Test cases

- Progress through planned/in-progress/delivered.
- Reject stale and unassigned/non-manager update.

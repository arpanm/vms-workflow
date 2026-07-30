# AFA-005 — Upload attendance data

**Persona:** ArrowFoundry administrator
**Implementation:** IMPLEMENTED — F02 imports and F06 governed migration
**Testing:** VERIFIED — workforce and migration system suites

## Description and UI flow

Use Workforce administration for current governed imports or Historical
migration for scanned CSV/XLSX attendance backfill.

## Acceptance criteria

- File is scanned before parsing; invalid/quarantined bytes cannot stage.
- Employee/date/timezone identities reconcile deterministically.
- Validation, approval and commit evidence are retained.

## Test cases

- Import valid current attendance.
- Quarantine malicious input and report row-level errors.

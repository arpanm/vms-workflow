# AFA-014 — Bulk import historical attendance

**Persona:** ArrowFoundry administrator/migration lead
**Implementation:** IMPLEMENTED — F06 attendance migration template
**Testing:** VERIFIED — migration backend 32/32 and system 7/7

## Description and UI flow

Download the attendance template, upload past-day data, scan, validate,
reconcile, dual-approve and commit through Historical migration.

## Acceptance criteria

- Import preserves represented versus recorded time and employee timezone.
- Reconciliation uses the latest effective finalized roster.
- Partial/rejected rows and compensation remain explicit.

## Test cases

- Commit clean past attendance.
- Quarantine unsafe bytes and reconcile missing employee-days.

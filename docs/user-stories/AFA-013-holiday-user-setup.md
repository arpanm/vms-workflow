# AFA-013 — Manage holidays and employee leave setup

**Persona:** ArrowFoundry administrator/HR
**Implementation:** IMPLEMENTED — F02 employee/calendar/leave-policy administration
**Testing:** VERIFIED — workforce completion suites

## Description and UI flow

Use Workforce administration to add/activate an employee, establish leave
opening adjustments and publish versioned holiday calendars.

## Acceptance criteria

- Employee lifecycle and leave commands are effective-dated and audited.
- Published calendar versions are immutable.
- Overlapping/invalid dates and duplicate holidays are rejected.

## Test cases

- Add employee, opening balance and holiday calendar.
- Reject overlapping or retroactively destructive updates.

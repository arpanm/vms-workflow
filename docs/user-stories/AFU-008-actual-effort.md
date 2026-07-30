# AFU-008 — Record actual effort and totals

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — per-user dated effort ledger
**Testing:** AUTOMATED — sum/date/authority tests

## Description and UI flow

Enter hours and note under **Actual effort**; the UI records today’s date and
shows the total across all contributors.

## Acceptance criteria

- Each entry is 0–24 hours for a real date and active participant.
- Entries remain distinct by user/date and total additively.
- User may record self; manager may correct for another participant.

## Test cases

- Two users’ entries sum correctly.
- Reject over-24 hours and unauthorized target.

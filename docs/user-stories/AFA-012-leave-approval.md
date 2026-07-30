# AFA-012 — Approve or reject leave

**Persona:** ArrowFoundry administrator/HR approver
**Implementation:** IMPLEMENTED — F02 versioned leave decisions
**Testing:** VERIFIED — workforce integration/system suites

## Description and UI flow

Open Leave, review dates/balance/policy and record approval or rejection.

## Acceptance criteria

- Server derives approver authority and prohibits unauthorized self-approval.
- Decision consumes/releases balance exactly once.
- Concurrent/stale decisions fail without double ledger effects.

## Test cases

- Approve and reject distinct requests.
- Exercise stale decision and insufficient balance.

# AFU-002 — Update my task delivery status

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — assignee-guarded status API/UI
**Testing:** AUTOMATED — assignment and concurrency assertions

## Description and UI flow

From an assigned task, choose status, add a delivery summary and select
**Update status**.

## Acceptance criteria

- Active assignee or manager permission is required.
- Version conflict prevents overwriting another update.
- Status/summary/actor/time are audited.

## Test cases

- Assigned user updates to in-progress and delivered.
- Unassigned user and stale version fail.

# AFU-003 — Transfer, add an assignee or claim a task

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — guarded V46 assignments
**Testing:** AUTOMATED — claim/transfer/foreign-user tests

## Description and UI flow

Use **Assign or claim** to add another practitioner/discipline to an assigned
task, or enter your own profile ID to claim a task.

## Acceptance criteria

- Existing assignee may add another active participant; anyone with permission
  may claim only themselves; managers may assign any participant.
- Cross-engagement/inactive users are rejected.
- Duplicate assignment is idempotent.

## Test cases

- Self-claim and assignee-to-colleague flow pass.
- Unassigned user assigning a third party fails.

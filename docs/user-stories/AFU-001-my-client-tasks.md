# AFU-001 — Browse clients and my assigned tasks

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — global client scope and `assignedToMe` filter
**Testing:** AUTOMATED — identity-derived personal-filter tests

## Description and UI flow

Select a client/engagement, open Client work items and enable **Assigned to
me**.

## Acceptance criteria

- Current user comes from JWT subject, not a request parameter.
- Only active assignments in the selected authorized engagement appear.
- Multiple disciplines on one task do not duplicate the task.

## Test cases

- Show tasks assigned to current developer across selected client.
- Exclude other users and inactive assignments.

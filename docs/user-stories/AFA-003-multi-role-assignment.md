# AFA-003 — Assign multiple practitioners and disciplines

**Persona:** ArrowFoundry administrator
**Implementation:** IMPLEMENTED — appendable V46 assignments
**Testing:** AUTOMATED — assignment/participant integration test

## Description and UI flow

Use **Assign or claim** repeatedly to add Developer, QA, Product Manager,
Program Manager, UX Designer, DevOps, Data Analyst or Other participants.

## Acceptance criteria

- Every assignee is an active engagement participant.
- Multiple users/disciplines coexist and duplicates are idempotent.
- Assignment actor/time are retained.

## Test cases

- Assign three users in different disciplines.
- Reject inactive and foreign-tenant users.

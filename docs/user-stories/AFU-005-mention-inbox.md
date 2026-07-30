# AFU-005 — See tasks where I am tagged

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — `mentionedToMe` filter
**Testing:** AUTOMATED — personal mention browser/API test

## Description and UI flow

Enable **Mentioned** on Client work items to show conversations requiring the
current user’s response.

## Acceptance criteria

- Filter is based on authenticated profile.
- One task appears once even with multiple mentions.
- Engagement authorization is always applied first.

## Test cases

- Multiple mentions yield one task.
- Mention to another profile does not appear.

# AFU-012 — Browse past, current and future client tasks

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — timeline filters with client scope
**Testing:** AUTOMATED — timeline browser/API test

## Description and UI flow

Select a client and choose `PAST`, `CURRENT`, `NEXT`, `BACKLOG` or `ALL`;
optionally combine with **Assigned to me**.

## Acceptance criteria

- Timeline is derived from engagement months.
- All-client view requires `workitem.read`; personal filter is identity-bound.
- Past delivered evidence remains readable, not editable through closed month.

## Test cases

- Verify all five buckets and personal intersection.
- Cross-client records never appear.

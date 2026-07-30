# AFA-010 — Browse past tasks and invoices

**Persona:** ArrowFoundry administrator
**Implementation:** IMPLEMENTED — `PAST` work-item filter and F05 invoice history
**Testing:** AUTOMATED — collaboration plus finance regression

## Description and UI flow

Choose **PAST** on Client work items and use Finance invoice history to inspect
previous delivered work and immutable invoice/package versions.

## Acceptance criteria

- Historical tasks remain readable after month closure.
- Invoice history is cursor-paginated and tenant-scoped.
- Past evidence cannot be rewritten through current task controls.

## Test cases

- View prior-month task and invoice.
- Deny foreign-client history.

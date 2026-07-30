# AFU-007 — Delete a prior estimate with authority

**Persona:** ArrowFoundry practitioner/manager
**Implementation:** IMPLEMENTED — authorized soft delete
**Testing:** AUTOMATED — owner/manager/denial tests

## Description and UI flow

Select the delete icon beside an estimate. Owners can remove their estimate;
scoped managers can correct any prior estimate while preserving audit history.

## Acceptance criteria

- Deletion is soft, records actor/time and immediately recalculates total.
- Non-owner non-manager is denied.
- Repeated delete returns not found and never double-mutates.

## Test cases

- Owner and manager delete paths pass.
- Unrelated user deletion fails.

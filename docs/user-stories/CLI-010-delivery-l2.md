# CLI-010 — L2 approve delivery status

**Persona:** Client L2 approver
**Implementation:** IMPLEMENTED — `DELIVERY_L2` V46 approval
**Testing:** AUTOMATED — distinct authority test; [execution](TEST_EXECUTION.md)

## Description and UI flow

After reviewing the task and prior decisions, choose `DELIVERY_L2` and record
the final client decision against the current version.

## Acceptance criteria

- `workitem.delivery.approve.l2` is independently required.
- L2 cannot be forged from client-supplied roles.
- Decision is append-only and visible alongside L1 evidence.

## Test cases

- Distinct L2 actor approves after L1 and both decisions render.
- L1-only actor cannot submit L2.

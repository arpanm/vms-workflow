# CLI-009 — L1 approve delivery status

**Persona:** Client L1 approver
**Implementation:** IMPLEMENTED — `DELIVERY_L1` V46 approval
**Testing:** AUTOMATED — permission/version integration tests; [execution](TEST_EXECUTION.md)

## Description and UI flow

Review delivery status and evidence, choose `DELIVERY_L1`, enter decision and
comment, then record the version-bound decision.

## Acceptance criteria

- L1 authority is server-derived.
- Approval captures exact task version and authenticated actor.
- Rejected/changes-requested decisions remain visible with comments.

## Test cases

- L1 approve/reject/changes-requested paths persist.
- Stale and non-L1 actors are denied.

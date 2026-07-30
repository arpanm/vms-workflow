# AFU-010 — Check in and check out

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — F02 Today self-service
**Testing:** VERIFIED — workforce browser/system suites

## Description and UI flow

Open **Today** to check in, record breaks and check out under the effective
shift/timezone policy.

## Acceptance criteria

- Employee identity is linked to authenticated user.
- Events are append-only, ordered and idempotent.
- Overnight and split sessions calculate correctly.

## Test cases

- Check-in/break/check-out sequence.
- Reject duplicate/out-of-order and another employee’s event.

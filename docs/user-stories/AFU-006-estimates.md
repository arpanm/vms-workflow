# AFU-006 — Record multiple estimates and totals

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — append-only per-user estimate ledger
**Testing:** AUTOMATED — aggregate/authority integration tests

## Description and UI flow

Enter hours and a note under **Estimate**. Every user’s estimates remain
separate while the card shows their active sum.

## Acceptance criteria

- Positive bounded hours are required.
- User may estimate for self; manager may enter another participant’s estimate.
- Total excludes soft-deleted estimates.

## Test cases

- Two users add estimates and total equals their sum.
- Reject zero/negative and unauthorized target user.

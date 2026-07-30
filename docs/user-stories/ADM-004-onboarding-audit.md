# ADM-004 — Audit onboarding and permission changes

**Persona:** Platform/ArrowFoundry administrator/auditor
**Implementation:** IMPLEMENTED — immutable V46 audit events and existing RBAC records
**Testing:** AUTOMATED — immutability and event assertions; [execution](TEST_EXECUTION.md)

## Description and UI flow

Every client/task administration mutation records authenticated actor, scope,
event type, time and non-sensitive details. Audit evidence is visible through
the governed reporting/audit surfaces, not editable UI fields.

## Acceptance criteria

- Client and work-item creation record actor-bound events in the same
  transaction.
- Audit rows reject update/delete.
- Passwords, tokens and unrestricted comment content are absent from details.

## Test cases

- Verify onboarding/task events and actor subjects.
- Attempt audit update/delete and expect database rejection.

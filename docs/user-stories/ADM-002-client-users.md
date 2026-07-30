# ADM-002 — Add multiple client users

**Persona:** Platform/ArrowFoundry administrator
**Implementation:** IMPLEMENTED — client user API and onboarding UI
**Testing:** AUTOMATED — `ClientCollaborationIT`; [execution](TEST_EXECUTION.md)

## Description and UI flow

After onboarding, add any number of client identities with name, email,
identity subject, effective dates and initial roles from the same screen.

## Acceptance criteria

- Each identity receives an active profile, client membership and role
  assignment; repeat submission safely reactivates the same identity.
- Email/subject uniqueness and effective-date rules are enforced.
- The response lists roles and derived permissions, never client-supplied
  permission claims.

## Test cases

- Add two users with different roles and verify both are listed.
- Reject invalid role/date and unauthorized vendor caller.

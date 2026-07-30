# AFU-004 — Comment and tag client/vendor users

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — V46 comments/mentions
**Testing:** AUTOMATED — mixed-party mention test

## Description and UI flow

Post task comments and provide client or ArrowFoundry participant IDs in the
tag field.

## Acceptance criteria

- Both client and vendor active participants may be tagged.
- Cross-client/inactive profile IDs are rejected atomically.
- Author and creation time are immutable.

## Test cases

- Tag one client and one vendor participant.
- Reject a participant from a different client.

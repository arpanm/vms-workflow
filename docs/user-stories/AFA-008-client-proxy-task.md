# AFA-008 — Create tasks on behalf of a client

**Persona:** ArrowFoundry administrator
**Implementation:** IMPLEMENTED — `createdOnBehalfOfClient` provenance
**Testing:** AUTOMATED — collaboration integration/browser test

## Description and UI flow

Create a task with the on-behalf indicator through the collaboration API/bulk
flow; the stored task distinguishes vendor entry from client-originated work.

## Acceptance criteria

- Only authorized task creators can use the client scope.
- Authenticated creator and on-behalf flag are both retained.
- Proxy creation never creates client approval automatically.

## Test cases

- Create flagged task and verify provenance.
- Verify it still requires L1 plan approval.

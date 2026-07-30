# CLI-004 — Comment and tag participants

**Persona:** Client user
**Implementation:** IMPLEMENTED — comment/mention ledgers and workspace UI
**Testing:** AUTOMATED — participant and tenant-boundary tests; [execution](TEST_EXECUTION.md)

## Description and UI flow

Open a task, write a comment, enter one or more client/ArrowFoundry user IDs,
and post. The conversation displays author, time and tag count.

## Acceptance criteria

- Comments are non-empty, bounded and actor-attributed.
- Every mentioned user must be an active participant in the engagement.
- Cross-client mentions fail and create no partial comment/mention set.

## Test cases

- Comment with both client and vendor mentions.
- Reject inactive and cross-engagement users atomically.

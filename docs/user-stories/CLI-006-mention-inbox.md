# CLI-006 — See tasks where I am tagged

**Persona:** Client user
**Implementation:** IMPLEMENTED — `mentionedToMe` server filter and UI toggle
**Testing:** AUTOMATED — mention-inbox test; [execution](TEST_EXECUTION.md)

## Description and UI flow

Enable **Mentioned** to show tasks containing a comment that tags the current
authenticated profile.

## Acceptance criteria

- Identity derives from JWT subject, never a supplied user ID.
- Tagged tasks remain engagement-authorized and de-duplicated.
- Removing other timeline filters does not expose foreign-client mentions.

## Test cases

- Tagged current/future tasks appear once.
- Untagged and cross-client tasks do not appear.

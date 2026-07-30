# CLI-003 — Attach PRD, design and tracker links

**Persona:** Client user
**Implementation:** IMPLEMENTED — typed V46 links and link UI
**Testing:** AUTOMATED — validation/browser assertions; [execution](TEST_EXECUTION.md)

## Description and UI flow

Attach HTTPS links typed as PRD, user story, Figma, prototype, Linear, Jira,
document or other reference. Links render as safe new-tab anchors.

## Acceptance criteria

- Type is allow-listed and URL must use HTTPS.
- Duplicate type/URL on one task is rejected.
- Links remain scoped with the task and record their authenticated creator.

## Test cases

- Add each requested product/design/tracker link type.
- Reject HTTP/javascript URLs and duplicate links.

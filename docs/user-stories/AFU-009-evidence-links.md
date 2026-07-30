# AFU-009 — Attach design, code and test evidence

**Persona:** ArrowFoundry practitioner
**Implementation:** IMPLEMENTED — typed link ledger/UI
**Testing:** AUTOMATED — all requested link-type test

## Description and UI flow

Use **Add task link** for documents, code review, commit, Figma/prototype,
test cases and test-run reports.

## Acceptance criteria

- All requested types are first-class allow-listed values.
- Only HTTPS URLs render as safe external links.
- Link creator/time remain visible in API evidence.

## Test cases

- Add design, commit, review, test-case and test-run links.
- Reject unsafe scheme and duplicate.

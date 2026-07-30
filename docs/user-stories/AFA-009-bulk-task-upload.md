# AFA-009 — Bulk create client tasks

**Persona:** ArrowFoundry administrator
**Implementation:** IMPLEMENTED — atomic `POST /work-items/bulk` up to 500 rows
**Testing:** AUTOMATED — bulk atomicity integration test

## Description and UI flow

Submit a validated JSON task collection from the administrative import flow;
each row supports criteria, workflow, links and initial assignments.

## Acceptance criteria

- Request contains 1–500 tasks for exactly one engagement.
- Any invalid/duplicate/foreign row rolls back the complete batch.
- Every created row is audited and visible in normal filters.

## Test cases

- Import multiple backlog/next-month tasks.
- Verify one invalid row causes zero committed tasks.
